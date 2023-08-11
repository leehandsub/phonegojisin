package com.ssafy.phonesin.ui.onboarding

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.activity.addCallback
import androidx.navigation.fragment.findNavController
import com.ssafy.phonesin.R
import com.ssafy.phonesin.databinding.FragmentOnboardingModuleBinding
import com.ssafy.phonesin.ui.MainActivity
import com.ssafy.phonesin.ui.util.base.BaseFragment
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class OnBoardingModuleFragment : BaseFragment<FragmentOnboardingModuleBinding>(
    R.layout.fragment_onboarding_module
) {
    private lateinit var mainActivity: MainActivity
    override fun onCreateBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ): FragmentOnboardingModuleBinding {
        return FragmentOnboardingModuleBinding.inflate(inflater, container, false).apply {
            lifecycleOwner = viewLifecycleOwner
        }
    }

    override fun init() {
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {}
        setOnClick()
    }

    private fun setOnClick() = with(bindingNonNull) {
        textViewSkip.setOnClickListener {
            findNavController().navigate(R.id.module)

        }

        buttonNext.setOnClickListener {
            findNavController().navigate(R.id.onboardingPlanFragment)
        }
    }
}
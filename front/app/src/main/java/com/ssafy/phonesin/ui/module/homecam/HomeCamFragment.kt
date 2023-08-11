package com.ssafy.phonesin.ui.module.homecam

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.navigation.fragment.findNavController
import com.ssafy.phonesin.R
import com.ssafy.phonesin.databinding.FragmentHomeCamBinding
import com.ssafy.phonesin.databinding.FragmentModuleBinding

private const val ARG_PARAM1 = "param1"
private const val ARG_PARAM2 = "param2"

class HomeCamFragment : Fragment() {
    private lateinit var binding: FragmentHomeCamBinding

    private var param1: String? = null
    private var param2: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            param1 = it.getString(ARG_PARAM1)
            param2 = it.getString(ARG_PARAM2)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentHomeCamBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initToolbar()
    }

    private fun initToolbar() {
        binding.textViewPlus.toolbarBackButtonTitle.text = getString(R.string.moduleHomeCamDetail)
        binding.textViewPlus.toolbarBackButton.setOnClickListener {
            findNavController().navigate(R.id.action_homeCamFragment_to_camListFragment)
        }
    }
    companion object {
        @JvmStatic
        fun newInstance(param1: String, param2: String) =
            HomeCamFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_PARAM1, param1)
                    putString(ARG_PARAM2, param2)
                }
            }
    }
}